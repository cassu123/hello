package org.hello.reader.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.BookSeriesCrossRef
import org.hello.reader.core.database.entity.SeriesEntity

data class SeriesBookItemEntity(
    @Embedded val book: BookEntity,
    @ColumnInfo(name = "series_position") val position: Double?,
    @ColumnInfo(name = "series_featured") val featured: Boolean,
)

data class BookSeriesEntryEntity(
    @ColumnInfo(name = "series_uuid") val seriesUuid: String,
    @ColumnInfo(name = "series_name") val seriesName: String,
    @ColumnInfo(name = "position") val position: Double?,
    @ColumnInfo(name = "featured") val featured: Boolean,
)

@Dao
interface SeriesDao {

    @Query(
        """
        SELECT s.*
        FROM series s
        WHERE s.server_id = :serverId
          AND EXISTS (
              SELECT 1 FROM book_series bs
              INNER JOIN books b ON b.id = bs.book_id AND b.server_id = bs.server_id
              WHERE bs.series_uuid = s.uuid AND bs.server_id = :serverId AND b.user_id = :userId
          )
        ORDER BY s.name ASC
        """
    )
    fun observeSeries(serverId: String, userId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM book_series WHERE server_id = :serverId")
    fun observeAllBookSeries(serverId: String): Flow<List<BookSeriesCrossRef>>

    @Query(
        """
        SELECT s.*
        FROM series s
        WHERE s.server_id = :serverId
          AND EXISTS (
              SELECT 1 FROM book_series bs
              INNER JOIN books b ON b.id = bs.book_id AND b.server_id = bs.server_id
              WHERE bs.series_uuid = s.uuid AND bs.server_id = :serverId AND b.user_id = :userId
          )
        ORDER BY s.name ASC
        """
    )
    suspend fun getSeries(serverId: String, userId: String): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE server_id = :serverId AND uuid = :uuid LIMIT 1")
    fun observeSeriesByUuid(serverId: String, uuid: String): Flow<SeriesEntity?>

    @Query("SELECT * FROM series WHERE server_id = :serverId AND uuid = :uuid LIMIT 1")
    suspend fun getSeriesByUuid(serverId: String, uuid: String): SeriesEntity?

    @Query(
        """
        SELECT b.*, bs.position AS series_position, bs.featured AS series_featured
        FROM books b
        INNER JOIN book_series bs ON b.id = bs.book_id AND b.server_id = bs.server_id
        WHERE bs.server_id = :serverId AND b.user_id = :userId AND bs.series_uuid = :seriesUuid
        ORDER BY CASE WHEN bs.position IS NULL THEN 1 ELSE 0 END, bs.position ASC, b.title ASC
        """
    )
    fun observeBooksForSeries(serverId: String, userId: String, seriesUuid: String): Flow<List<SeriesBookItemEntity>>

    @Query(
        """
        SELECT b.*, bs.position AS series_position, bs.featured AS series_featured
        FROM books b
        INNER JOIN book_series bs ON b.id = bs.book_id AND b.server_id = bs.server_id
        WHERE bs.server_id = :serverId AND b.user_id = :userId AND bs.series_uuid = :seriesUuid
        ORDER BY CASE WHEN bs.position IS NULL THEN 1 ELSE 0 END, bs.position ASC, b.title ASC
        """
    )
    suspend fun getBooksForSeries(serverId: String, userId: String, seriesUuid: String): List<SeriesBookItemEntity>

    @Query(
        """
        SELECT s.uuid AS series_uuid, s.name AS series_name, bs.position AS position, bs.featured AS featured
        FROM series s
        INNER JOIN book_series bs ON s.uuid = bs.series_uuid AND s.server_id = bs.server_id
        WHERE bs.server_id = :serverId AND bs.book_id = :bookId
        ORDER BY CASE WHEN bs.position IS NULL THEN 1 ELSE 0 END, bs.position ASC, s.name ASC
        """
    )
    fun observeSeriesForBook(serverId: String, bookId: String): Flow<List<BookSeriesEntryEntity>>

    @Query(
        """
        SELECT s.uuid AS series_uuid, s.name AS series_name, bs.position AS position, bs.featured AS featured
        FROM series s
        INNER JOIN book_series bs ON s.uuid = bs.series_uuid AND s.server_id = bs.server_id
        WHERE bs.server_id = :serverId AND bs.book_id = :bookId
        ORDER BY CASE WHEN bs.position IS NULL THEN 1 ELSE 0 END, bs.position ASC, s.name ASC
        """
    )
    suspend fun getSeriesForBook(serverId: String, bookId: String): List<BookSeriesEntryEntity>

    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Upsert
    suspend fun upsertBookSeries(refs: List<BookSeriesCrossRef>)

    @Query("DELETE FROM book_series WHERE server_id = :serverId")
    suspend fun clearBookSeries(serverId: String)

    @Query("DELETE FROM series WHERE server_id = :serverId AND uuid NOT IN (:activeUuids)")
    suspend fun pruneOrphanedSeries(serverId: String, activeUuids: List<String>)

    @Query("DELETE FROM series WHERE server_id = :serverId")
    suspend fun clearSeries(serverId: String)
}
