package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus

@Entity(
    tableName = "book_downloads",
    primaryKeys = ["book_id", "server_id", "user_id", "format"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id", "server_id", "user_id"],
            childColumns = ["book_id", "server_id", "user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["server_id", "user_id"]),
        Index(value = ["book_id", "server_id", "user_id"]),
    ],
)
data class BookDownloadEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "format") val format: BookMediaType,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "download_status") val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    @ColumnInfo(name = "download_progress") val downloadProgress: Float = 0f,
    @ColumnInfo(name = "sha256") val sha256: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
