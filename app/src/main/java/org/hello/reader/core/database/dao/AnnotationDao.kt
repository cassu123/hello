package org.hello.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.hello.reader.core.database.entity.AnnotationEntity
import org.hello.reader.core.model.AnnotationType
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    @Query(
        """
        SELECT * FROM annotations
        WHERE server_id = :serverId AND user_id = :userId AND book_id = :bookId
        ORDER BY created_at DESC
        """,
    )
    fun observeAnnotations(serverId: String, userId: String, bookId: String): Flow<List<AnnotationEntity>>

    @Query(
        """
        SELECT * FROM annotations
        WHERE server_id = :serverId AND user_id = :userId AND book_id = :bookId AND type = :type
        ORDER BY created_at DESC
        """,
    )
    fun observeAnnotationsByType(
        serverId: String,
        userId: String,
        bookId: String,
        type: AnnotationType,
    ): Flow<List<AnnotationEntity>>

    /** Every annotation in two scopes (the active server's and the local library's), newest first. */
    @Query(
        """
        SELECT * FROM annotations
        WHERE (server_id = :serverId AND user_id = :userId)
           OR (server_id = :localServerId AND user_id = :localUserId)
        ORDER BY created_at DESC
        """,
    )
    fun observeAnnotationsInScopes(
        serverId: String,
        userId: String,
        localServerId: String,
        localUserId: String,
    ): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE id = :id AND server_id = :serverId AND user_id = :userId")
    suspend fun getAnnotation(id: String, serverId: String, userId: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id AND server_id = :serverId AND user_id = :userId")
    suspend fun deleteAnnotation(id: String, serverId: String, userId: String)

    @Query("DELETE FROM annotations WHERE server_id = :serverId AND user_id = :userId")
    suspend fun clearServerAnnotations(serverId: String, userId: String)
}
