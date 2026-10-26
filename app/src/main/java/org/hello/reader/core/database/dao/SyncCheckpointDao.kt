package org.hello.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.hello.reader.core.database.entity.SyncCheckpointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncCheckpointDao {
    @Query("SELECT * FROM sync_checkpoints WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    fun observeCheckpoint(bookId: String, serverId: String, userId: String): Flow<SyncCheckpointEntity?>

    @Query("SELECT * FROM sync_checkpoints WHERE server_id = :serverId AND user_id = :userId")
    fun observeAllCheckpoints(serverId: String, userId: String): Flow<List<SyncCheckpointEntity>>

    @Query("SELECT * FROM sync_checkpoints WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    suspend fun getCheckpoint(bookId: String, serverId: String, userId: String): SyncCheckpointEntity?

    @Query("SELECT * FROM sync_checkpoints WHERE server_id = :serverId AND user_id = :userId AND is_dirty = 1")
    suspend fun getDirtyCheckpoints(serverId: String, userId: String): List<SyncCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: SyncCheckpointEntity)

    @Query("UPDATE sync_checkpoints SET is_dirty = 0 WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    suspend fun markSynced(bookId: String, serverId: String, userId: String)
}
