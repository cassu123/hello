package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "book_collections",
    primaryKeys = ["book_id", "server_id", "collection_uuid"],
    indices = [
        Index(value = ["server_id", "collection_uuid"]),
        Index(value = ["server_id", "book_id"]),
    ],
)
data class BookCollectionCrossRef(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "collection_uuid") val collectionUuid: String,
)
