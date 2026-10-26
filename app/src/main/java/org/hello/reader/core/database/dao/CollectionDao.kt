package org.hello.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.hello.reader.core.database.entity.BookCollectionCrossRef
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.CollectionEntity

@Dao
interface CollectionDao {

    @Query(
        """
        SELECT c.*
        FROM collections c
        WHERE c.server_id = :serverId
          AND EXISTS (
              SELECT 1 FROM book_collections bc
              INNER JOIN books b ON b.id = bc.book_id AND b.server_id = bc.server_id
              WHERE bc.collection_uuid = c.uuid AND bc.server_id = :serverId AND b.user_id = :userId
          )
        ORDER BY c.name ASC
        """
    )
    fun observeCollections(serverId: String, userId: String): Flow<List<CollectionEntity>>

    @Query(
        """
        SELECT c.*
        FROM collections c
        WHERE c.server_id = :serverId
          AND EXISTS (
              SELECT 1 FROM book_collections bc
              INNER JOIN books b ON b.id = bc.book_id AND b.server_id = bc.server_id
              WHERE bc.collection_uuid = c.uuid AND bc.server_id = :serverId AND b.user_id = :userId
          )
        ORDER BY c.name ASC
        """
    )
    suspend fun getCollections(serverId: String, userId: String): List<CollectionEntity>

    @Query("SELECT * FROM book_collections WHERE server_id = :serverId")
    fun observeAllBookCollections(serverId: String): Flow<List<BookCollectionCrossRef>>

    @Query("SELECT * FROM collections WHERE server_id = :serverId AND uuid = :uuid LIMIT 1")
    fun observeCollectionByUuid(serverId: String, uuid: String): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE server_id = :serverId AND uuid = :uuid LIMIT 1")
    suspend fun getCollectionByUuid(serverId: String, uuid: String): CollectionEntity?

    @Query(
        """
        SELECT c.*
        FROM collections c
        INNER JOIN book_collections bc ON c.uuid = bc.collection_uuid AND c.server_id = bc.server_id
        WHERE bc.server_id = :serverId AND bc.book_id = :bookId
        ORDER BY c.name ASC
        """
    )
    fun observeCollectionsForBook(serverId: String, bookId: String): Flow<List<CollectionEntity>>

    @Query(
        """
        SELECT c.*
        FROM collections c
        INNER JOIN book_collections bc ON c.uuid = bc.collection_uuid AND c.server_id = bc.server_id
        WHERE bc.server_id = :serverId AND bc.book_id = :bookId
        ORDER BY c.name ASC
        """
    )
    suspend fun getCollectionsForBook(serverId: String, bookId: String): List<CollectionEntity>

    @Query(
        """
        SELECT b.*
        FROM books b
        INNER JOIN book_collections bc ON b.id = bc.book_id AND b.server_id = bc.server_id
        WHERE bc.server_id = :serverId AND b.user_id = :userId AND bc.collection_uuid = :collectionUuid
        ORDER BY b.title ASC
        """
    )
    fun observeBooksForCollection(serverId: String, userId: String, collectionUuid: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT b.*
        FROM books b
        INNER JOIN book_collections bc ON b.id = bc.book_id AND b.server_id = bc.server_id
        WHERE bc.server_id = :serverId AND b.user_id = :userId AND bc.collection_uuid = :collectionUuid
        ORDER BY b.title ASC
        """
    )
    suspend fun getBooksForCollection(serverId: String, userId: String, collectionUuid: String): List<BookEntity>

    @Upsert
    suspend fun upsertCollections(collections: List<CollectionEntity>)

    @Upsert
    suspend fun upsertBookCollections(refs: List<BookCollectionCrossRef>)

    @Query("DELETE FROM book_collections WHERE server_id = :serverId")
    suspend fun clearBookCollections(serverId: String)

    @Query("DELETE FROM collections WHERE server_id = :serverId AND uuid NOT IN (:activeUuids)")
    suspend fun pruneOrphanedCollections(serverId: String, activeUuids: List<String>)

    @Query("DELETE FROM collections WHERE server_id = :serverId")
    suspend fun clearCollections(serverId: String)
}
