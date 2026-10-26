package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.hello.reader.core.model.SyncCheckpoint

@Entity(
    tableName = "sync_checkpoints",
    primaryKeys = ["book_id", "server_id", "user_id"],
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
        Index(value = ["server_id", "user_id", "is_dirty"]),
    ],
)
data class SyncCheckpointEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "reading_cfi") val readingCfi: String?,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String?,
    @ColumnInfo(name = "audio_position_ms") val audioPositionMs: Long,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long,
    @ColumnInfo(name = "progress_percent") val progressPercent: Float,
    @ColumnInfo(name = "active_sentence_id") val activeSentenceId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "is_dirty") val isDirty: Boolean,
    @ColumnInfo(name = "chapter_progression") val chapterProgression: Float? = null,
) {
    fun toDomain(): SyncCheckpoint = SyncCheckpoint(
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
        updatedAt = updatedAt,
        isDirty = isDirty,
        chapterProgression = chapterProgression,
    )

    companion object {
        fun fromDomain(checkpoint: SyncCheckpoint): SyncCheckpointEntity = SyncCheckpointEntity(
            bookId = checkpoint.bookId,
            serverId = checkpoint.serverId,
            userId = checkpoint.userId,
            readingCfi = checkpoint.readingCfi,
            chapterIndex = checkpoint.chapterIndex,
            chapterTitle = checkpoint.chapterTitle,
            audioPositionMs = checkpoint.audioPositionMs,
            totalDurationMs = checkpoint.totalDurationMs,
            progressPercent = checkpoint.progressPercent,
            activeSentenceId = checkpoint.activeSentenceId,
            updatedAt = checkpoint.updatedAt,
            isDirty = checkpoint.isDirty,
            chapterProgression = checkpoint.chapterProgression,
        )
    }
}
